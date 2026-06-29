package com.ai.ui.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Settings
import com.ai.ui.shared.AppColors
import com.ai.ui.shared.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One aggregated row of the Model statistics screen: a base model
 *  name (version + provider-namespace stripped) with how many distinct
 *  versions exist for it and how many distinct providers carry it. */
data class ModelStat(val baseName: String, val versions: Int, val providers: Int)

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

/** A token starts version / size / variant info when it carries a digit
 *  (5.2, k2.6, 70b, v4, r1, 2024, 4o, a3b, fp8) or is a known variant
 *  word ([VARIANT_TOKENS]). */
private fun isVersionOrVariantToken(token: String): Boolean =
    token.any(Char::isDigit) || token in VARIANT_TOKENS

/** Strip a model id down to its base name — the part that identifies the
 *  *model*, with the provider/org namespace, the version, and any size /
 *  variant qualifiers removed. Groups "the same model — different
 *  version / size / variant / provider" together on the Model statistics
 *  screen.
 *
 *  Steps:
 *   1. Drop the namespace prefix — everything up to and including the
 *      last `/` — then lower-case (`z-ai/glm-5.2` → `glm-5.2`,
 *      `accounts/fireworks/models/deepseek-v4` → `deepseek-v4`).
 *   2. Split on `-` `_` `:` and whitespace (a `.` is NOT a separator, so
 *      a version like `5.2` / `k2.6` stays one token).
 *   3. Always keep the first token as the name root (it may itself carry
 *      an attached version such as `qwen3` / `phi3` — left intact), then
 *      append the following tokens until the first version-or-variant
 *      token, stopping there.
 *
 *  Examples: `glm-5.2` → `glm`, `kimi-k2.7-code` → `kimi`,
 *  `qwen3-coder` → `qwen3`, `deepseek-r1` → `deepseek`,
 *  `llama-3.3-70b-instruct` → `llama`, `gpt-oss-120b` → `gpt-oss`,
 *  `command-r` → `command-r`. Heuristic by nature — model naming has no
 *  standard; tune [VARIANT_TOKENS] to taste. */
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

/** Aggregate every (provider, model) pair across all active providers
 *  into per-base-model statistics. A "version" is the model id with its
 *  namespace prefix stripped + lower-cased (so `z-ai/glm-5.1` and the
 *  bare `glm-5.1` count once); a "provider" is the AppService id.
 *  Sorted by provider coverage, then version count, then name. */
internal fun computeModelStatistics(aiSettings: Settings): List<ModelStat> {
    class Acc {
        val versionKeys = HashSet<String>()
        val providerIds = HashSet<String>()
    }
    val groups = LinkedHashMap<String, Acc>()
    for (service in aiSettings.getActiveServices()) {
        for (model in aiSettings.getModels(service)) {
            if (model.isBlank()) continue
            val base = baseModelName(model)
            if (base.isBlank()) continue
            val acc = groups.getOrPut(base) { Acc() }
            acc.versionKeys.add(model.substringAfterLast('/').lowercase().trim())
            acc.providerIds.add(service.id)
        }
    }
    return groups
        .map { (base, acc) -> ModelStat(base, acc.versionKeys.size, acc.providerIds.size) }
        .sortedBy { it.baseName }
}

/** AI Setup → Models → Model statistics. Read-only table grouping the
 *  full model catalog by base model name, showing how many versions
 *  and how many providers each base model spans. */
@Composable
fun ModelStatisticsScreen(
    aiSettings: Settings,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val stats by produceState<List<ModelStat>?>(initialValue = null, aiSettings) {
        value = withContext(Dispatchers.IO) { computeModelStatistics(aiSettings) }
    }
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
            "${list.size} base model${if (list.size == 1) "" else "s"} across all active providers",
            fontSize = 11.sp, color = AppColors.TextTertiary,
            modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
        )
        // Header row.
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
            items(list, key = { it.baseName }) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.baseName, modifier = Modifier.weight(1f), fontSize = 14.sp,
                        color = AppColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${s.versions}", modifier = Modifier.width(78.dp), textAlign = TextAlign.End,
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                    Text("${s.providers}", modifier = Modifier.width(88.dp), textAlign = TextAlign.End,
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = AppColors.TextSecondary)
                }
                HorizontalDivider(color = AppColors.TextDisabled.copy(alpha = 0.4f), thickness = 1.dp)
            }
        }
    }
}
