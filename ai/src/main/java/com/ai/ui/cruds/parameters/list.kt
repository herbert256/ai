package com.ai.ui.cruds.parameters

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Parameters
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: Parameters) : Mode
    data class Edit(val item: Parameters) : Mode
    data object Add : Mode
}

private fun setCount(p: Parameters): Int = listOfNotNull(
    p.temperature, p.maxTokens, p.topP, p.topK, p.frequencyPenalty,
    p.presencePenalty, p.seed, p.systemPrompt?.takeIf { it.isNotBlank() },
    p.reasoningEffort?.takeIf { it.isNotBlank() }
).size + (if (p.searchEnabled) 1 else 0) + (if (p.webSearchTool) 1 else 0)

@Composable
fun ParametersCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (Parameters) -> Unit = { saved ->
        val list = aiSettings.parameters
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(parameters = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Parameters",
            helpTopic = "parameters_list",
            items = aiSettings.parameters.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${setCount(it)} set" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No parameter presets configured"
        )
        is Mode.View -> ParametersView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) },
            onDelete = { onSave(aiSettings.removeParameters(m.item.id)); toList() },
            onBack = toList
        )
        is Mode.Edit -> ParametersEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> ParametersAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
