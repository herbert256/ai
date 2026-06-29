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

/** Strip a model id down to its base name — the part that identifies
 *  the *model*, with the provider/org namespace and the version
 *  information removed. Used to group "the same model, different
 *  version / different provider" together on the Model statistics
 *  screen.
 *
 *  Steps:
 *   1. Drop the namespace prefix — everything up to and including the
 *      last `/` (so `z-ai/glm-5.2` → `glm-5.2`,
 *      `accounts/fireworks/models/deepseek-v4` → `deepseek-v4`).
 *   2. Cut at the first version marker — a separator (`-` `_` `:` or
 *      whitespace) followed by an optional `v`/`V` and a digit. So
 *      `glm-5.2` → `glm`, `claude-haiku-4-5` → `claude-haiku`,
 *      `deepseek-v4` → `deepseek`, `o1-2024-12-17` → `o1`.
 *   3. Lower-case so casing differences fold together.
 *
 *  Imperfect by nature (model naming has no standard), but it matches
 *  the common `name-version` shape; a `.` is intentionally NOT a
 *  separator so `5.2` stays one version token. */
internal fun baseModelName(modelId: String): String {
    val afterSlash = modelId.substringAfterLast('/')
    val marker = Regex("[-_:\\s][vV]?\\d").find(afterSlash)
    val base = if (marker != null) afterSlash.substring(0, marker.range.first) else afterSlash
    return base.trim().lowercase()
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
        .sortedWith(
            compareByDescending<ModelStat> { it.providers }
                .thenByDescending { it.versions }
                .thenBy { it.baseName }
        )
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
