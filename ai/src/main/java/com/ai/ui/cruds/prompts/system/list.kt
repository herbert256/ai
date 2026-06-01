package com.ai.ui.cruds.prompts.system

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.Settings
import com.ai.model.SystemPrompt
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
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
    var confirmDelete by remember { mutableStateOf<SystemPrompt?>(null) }
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
            subject = "Reusable system instructions",
            helpTopic = "crud_system_prompts",
            items = aiSettings.systemPrompts.sortedBy { it.name.lowercase() },
            line = { "${it.name} · ${it.prompt.lineSequence().firstOrNull().orEmpty().take(50)}" },
            itemKey = { it.id },
            // Tap → straight to the edit screen (view skipped); 👯 / 🗑 on its bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No system prompts"
        )
        is Mode.Edit -> SystemPromptEdit(
            item = m.item, aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> SystemPromptAdd(
            aiSettings = aiSettings,
            onSaved = { saved -> upsert(saved) },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { sp ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete system prompt?") },
            text = { Text("Delete “${sp.name}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onSave(aiSettings.removeSystemPrompt(sp.id)); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
