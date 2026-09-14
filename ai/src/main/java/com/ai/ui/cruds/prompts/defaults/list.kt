package com.ai.ui.cruds.prompts.defaults

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.DefaultPrompt
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
    data class Edit(val item: DefaultPrompt) : Mode
    data object Add : Mode
}

@Composable
fun DefaultPromptsCrud(
    aiSettings: Settings,
    onSave: (Settings) -> Unit,
    onBack: () -> Unit,
    onNavigateHome: () -> Unit
) {
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    var confirmDelete by remember { mutableStateOf<DefaultPrompt?>(null) }
    val toList = { mode = Mode.List }
    val upsert: (DefaultPrompt) -> Unit = { saved ->
        val list = aiSettings.defaultPrompts
        val updated = if (list.any { it.id == saved.id }) list.map { if (it.id == saved.id) saved else it }
                      else list + saved
        onSave(aiSettings.copy(defaultPrompts = updated))
    }

    when (val m = mode) {
        Mode.List -> CrudListPage(
            title = "Default prompts",
            subject = "Reusable worker prompts",
            helpTopic = "crud_default_prompts",
            items = aiSettings.defaultPrompts.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${it.prompt.lineSequence().firstOrNull().orEmpty().take(50)}" },
            itemKey = { it.id },
            // Tap → straight to the edit screen (view skipped); 👯 / 🗑 on its bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No default prompts"
        )
        is Mode.Edit -> DefaultPromptEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> DefaultPromptAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { sp ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete default prompt?") },
            text = { Text("Delete “${sp.name}”? Assignments on agents, flocks and swarms will also be cleared.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onSave(aiSettings.removeDefaultPrompt(sp.id)); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
