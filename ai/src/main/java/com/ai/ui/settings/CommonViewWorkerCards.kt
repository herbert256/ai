package com.ai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.model.Parameters
import com.ai.model.Settings
import com.ai.ui.shared.AppColors

/**
 * Shared System-prompt + Parameters cards rendered at the bottom of
 * every Worker view screen (AgentView / FlockView / SwarmView). All
 * three carry the same `paramsIds` + `systemPromptId` shape so the
 * rendering is identical.
 */
@Composable
internal fun WorkerSharedCards(
    aiSettings: Settings,
    paramsIds: List<String>,
    systemPromptId: String?
) {
    systemPromptId?.let { id ->
        val sp = aiSettings.getSystemPromptById(id)
        if (sp != null) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.CardBackground)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("System prompt", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
                Text(sp.name, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (sp.prompt.isNotBlank()) {
                    Text(
                        sp.prompt,
                        fontSize = 12.sp, color = AppColors.TextSecondary,
                        maxLines = 8, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    val presets = paramsIds.mapNotNull { id -> aiSettings.getParametersById(id) }
    if (presets.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppColors.CardBackground)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Parameters", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AppColors.Blue)
            presets.forEach { preset ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(preset.name, fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    parametersAsRows(preset).forEach { (k, v) ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(k, fontSize = 12.sp, color = AppColors.TextTertiary)
                            Text(v, fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

private fun parametersAsRows(p: Parameters): List<Pair<String, String>> = buildList {
    p.temperature?.let { add("Temperature" to it.toString()) }
    p.maxTokens?.let { add("Max tokens" to it.toString()) }
    p.topP?.let { add("Top P" to it.toString()) }
    p.topK?.let { add("Top K" to it.toString()) }
    p.frequencyPenalty?.let { add("Frequency penalty" to it.toString()) }
    p.presencePenalty?.let { add("Presence penalty" to it.toString()) }
    p.seed?.let { add("Seed" to it.toString()) }
    if (p.responseFormatJson) add("Response format" to "JSON")
    if (p.searchEnabled) add("Search" to "enabled")
    if (p.webSearchTool) add("Web search tool" to "enabled")
    if (!p.returnCitations) add("Return citations" to "false")
    p.searchRecency?.takeIf { it.isNotBlank() }?.let { add("Search recency" to it) }
    p.reasoningEffort?.takeIf { it.isNotBlank() }?.let { add("Reasoning effort" to it) }
    p.stopSequences?.takeIf { it.isNotEmpty() }?.let { add("Stop sequences" to it.joinToString(", ")) }
    p.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System prompt" to it.take(80) + if (it.length > 80) "…" else "") }
}
