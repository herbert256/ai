package com.ai.ui.cruds.prompts.examples

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.model.ExamplePrompt
import com.ai.model.Settings
import com.ai.ui.cruds.framework.CrudListPage

private sealed interface Mode {
    data object List : Mode
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
    var confirmDelete by remember { mutableStateOf<ExamplePrompt?>(null) }
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
            subject = "Starter prompts for new reports",
            helpTopic = "crud_example_prompts",
            items = aiSettings.examplePrompts.sortedBy { it.title.lowercase() },
            line = { "${it.title.ifBlank { "(untitled)" }} · ${it.text.lineSequence().firstOrNull().orEmpty().take(50)}" },
            itemKey = { it.id },
            // Tap → straight to the edit screen (view skipped); 👯 / 🗑 on its bar.
            onView = { mode = Mode.Edit(it) },
            onAdd = { mode = Mode.Add },
            onBack = onBack,
            emptyMessage = "No example prompts"
        )
        is Mode.Edit -> ExamplePromptEdit(
            item = m.item,
            onSaved = { saved -> upsert(saved); toList() },
            onDelete = { confirmDelete = m.item },
            onBack = toList, onNavigateHome = onNavigateHome
        )
        Mode.Add -> ExamplePromptAdd(
            onSaved = { saved -> upsert(saved); toList() },
            onBack = toList, onNavigateHome = onNavigateHome
        )
    }

    confirmDelete?.let { ep ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete example prompt?") },
            text = { Text("Delete “${ep.title.ifBlank { "(untitled)" }}”? This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; onSave(aiSettings.removeExamplePrompt(ep.id)); toList() }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }
}
