package com.ai.ui.cruds.prompts.system

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.SystemPrompt
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: SystemPrompt) : Mode
    data class Edit(val item: SystemPrompt) : Mode
    data object Add : Mode
}

@Composable
fun SystemPromptsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (SystemPrompt) -> Unit = { saved ->
        val list = aiSettings.systemPrompts
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(systemPrompts = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "System prompts",
            helpTopic = "system_prompts",
            items = aiSettings.systemPrompts.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${it.prompt.lineSequence().firstOrNull().orEmpty().take(50)}" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No system prompts"
        )
        is Mode.View -> SystemPromptView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), name = "${m.item.name}-copy")) },
            onDelete = { onSave(aiSettings.removeSystemPrompt(m.item.id)); toList() },
            onBack = toList
        )
        is Mode.Edit -> SystemPromptEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> SystemPromptAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
