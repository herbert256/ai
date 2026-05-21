package com.ai.ui.cruds.parameters

import androidx.compose.runtime.Composable
import com.ai.model.Parameters
import com.ai.ui.cruds.framework.CrudField
import com.ai.ui.cruds.framework.CrudViewPage

private fun rows(p: Parameters): List<Pair<String, String>> = buildList {
    p.temperature?.let { add("Temperature" to it.toString()) }
    p.maxTokens?.let { add("Max tokens" to it.toString()) }
    p.topP?.let { add("top_p" to it.toString()) }
    p.topK?.let { add("top_k" to it.toString()) }
    p.frequencyPenalty?.let { add("Frequency penalty" to it.toString()) }
    p.presencePenalty?.let { add("Presence penalty" to it.toString()) }
    p.seed?.let { add("Seed" to it.toString()) }
    p.reasoningEffort?.takeIf { it.isNotBlank() }?.let { add("Reasoning effort" to it) }
    if (p.responseFormatJson) add("Response format" to "JSON")
    if (p.searchEnabled) add("Search" to "enabled")
    if (p.webSearchTool) add("Web-search tool" to "enabled")
    p.searchRecency?.takeIf { it.isNotBlank() }?.let { add("Search recency" to it) }
    p.stopSequences?.takeIf { it.isNotEmpty() }?.let { add("Stop sequences" to it.joinToString(", ")) }
    p.systemPrompt?.takeIf { it.isNotBlank() }?.let { add("System prompt" to it) }
}

@Composable
internal fun ParametersView(
    item: Parameters,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    CrudViewPage(
        title = "Parameter preset",
        onEdit = onEdit, onCopy = onCopy, onDelete = onDelete, onBack = onBack,
        deleteName = item.name,
        helpTopic = "parameters_list"
    ) {
        CrudField("Name", item.name)
        val r = rows(item)
        if (r.isEmpty()) CrudField("Parameters", "(none set)")
        else r.forEach { (k, v) -> CrudField(k, v) }
    }
}
