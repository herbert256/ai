package com.ai.ui.cruds.prompts.examples

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.ExamplePrompt
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage
import java.util.UUID

private sealed interface Mode {
    data object List : Mode
    data class View(val item: ExamplePrompt) : Mode
    data class Edit(val item: ExamplePrompt) : Mode
    data object Add : Mode
}

@Composable
fun ExamplePromptsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    val toList = { mode = Mode.List }
    val upsert: (ExamplePrompt) -> Unit = { saved ->
        val list = aiSettings.examplePrompts
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(examplePrompts = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Example prompts",
            helpTopic = "example_prompts",
            items = aiSettings.examplePrompts.sortedBy { it.title.lowercase() },
            line = { "${it.title.ifBlank { "(untitled)" }} · ${it.text.lineSequence().firstOrNull().orEmpty().take(50)}" },
            itemKey = { it.id },
            onView = { mode = Mode.View(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No example prompts"
        )
        is Mode.View -> ExamplePromptView(
            item = m.item,
            onEdit = { mode = Mode.Edit(m.item) },
            onCopy = { mode = Mode.Edit(m.item.copy(id = UUID.randomUUID().toString(), title = "${m.item.title}-copy")) },
            onDelete = { onSave(aiSettings.removeExamplePrompt(m.item.id)); toList() },
            onBack = toList
        )
        is Mode.Edit -> ExamplePromptEdit(
            item = m.item,
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> ExamplePromptAdd(
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }
}
